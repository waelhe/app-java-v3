'use client';

import React from 'react';
import { Tag, Clock, Flame, ChevronLeft, ChevronRight, Store } from 'lucide-react';
import { motion } from 'framer-motion';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';

interface Offer {
  id: string;
  title: string;
  titleEn: string;
  store: string;
  storeEn: string;
  discount: string;
  oldPrice: string;
  newPrice: string;
  endTime: string;
  image: string;
  badge?: string;
  badgeEn?: string;
}

const qudsayaCenterOffers: Offer[] = [
  {
    id: '1',
    title: 'عرض خاص على المنتجات الغذائية',
    titleEn: 'Special Food Offer',
    store: 'سوبر ماركت قدسيا',
    storeEn: 'Qudsaya Supermarket',
    discount: '30%',
    oldPrice: '50,000',
    newPrice: '35,000',
    endTime: 'ينتهي غداً',
    badge: 'محدود',
    badgeEn: 'Limited',
    image: 'https://images.unsplash.com/photo-1604719312566-8912e9227c6a?auto=format&fit=crop&w=300&q=80'
  },
  {
    id: '2',
    title: 'خصم على الملابس الشتوية',
    titleEn: 'Winter Clothes Sale',
    store: 'محل الأناقة',
    storeEn: 'Elegance Store',
    discount: '50%',
    oldPrice: '120,000',
    newPrice: '60,000',
    endTime: '3 أيام متبقية',
    badge: 'حصري',
    badgeEn: 'Exclusive',
    image: 'https://images.unsplash.com/photo-1489987707025-afc232f7ea0f?auto=format&fit=crop&w=300&q=80'
  },
  {
    id: '3',
    title: 'عروض الأجهزة الكهربائية',
    titleEn: 'Electronics Deals',
    store: 'إلكترون قدسيا',
    storeEn: 'Qudsaya Electronics',
    discount: '25%',
    oldPrice: '200,000',
    newPrice: '150,000',
    endTime: '5 أيام متبقية',
    image: 'https://images.unsplash.com/photo-1558618666-fcd25c85cd64?auto=format&fit=crop&w=300&q=80'
  }
];

const qudsayaDahiaOffers: Offer[] = [
  {
    id: '1',
    title: 'عرض خاص على المنتجات الغذائية',
    titleEn: 'Special Food Offer',
    store: 'سوبر ماركت الضاحية',
    storeEn: 'Dahia Supermarket',
    discount: '25%',
    oldPrice: '55,000',
    newPrice: '40,000',
    endTime: 'ينتهي بعد غد',
    badge: 'جديد',
    badgeEn: 'New',
    image: 'https://images.unsplash.com/photo-1604719312566-8912e9227c6a?auto=format&fit=crop&w=300&q=80'
  },
  {
    id: '2',
    title: 'خصم على الأجهزة الكهربائية',
    titleEn: 'Electronics Discount',
    store: 'محل الإلكترونيات',
    storeEn: 'Electronics Store',
    discount: '40%',
    oldPrice: '140,000',
    newPrice: '85,000',
    endTime: '5 أيام متبقية',
    badge: 'حصري',
    badgeEn: 'Exclusive',
    image: 'https://images.unsplash.com/photo-1558618666-fcd25c85cd64?auto=format&fit=crop&w=300&q=80'
  },
  {
    id: '3',
    title: 'عروض المطاعم',
    titleEn: 'Restaurant Offers',
    store: 'مطعم الليمون',
    storeEn: 'Lemon Restaurant',
    discount: '20%',
    oldPrice: '30,000',
    newPrice: '24,000',
    endTime: 'أسبوع متبقي',
    image: 'https://images.unsplash.com/photo-1565299624946-b28f40a0ae38?auto=format&fit=crop&w=300&q=80'
  }
];

const dataByRegion: Record<Region, Offer[]> = {
  'qudsaya-center': qudsayaCenterOffers,
  'qudsaya-dahia': qudsayaDahiaOffers
};

export default function FeaturedOffers() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  const offers = dataByRegion[region];

  const scrollRef = React.useRef<HTMLDivElement>(null);

  const scroll = (direction: 'left' | 'right') => {
    if (scrollRef.current) {
      const scrollAmount = 200;
      scrollRef.current.scrollBy({
        left: direction === 'left' ? -scrollAmount : scrollAmount,
        behavior: 'smooth'
      });
    }
  };

  return (
    <div className="mb-4 rounded-xl bg-gradient-to-r from-rose-500/10 via-orange-500/10 to-pink-500/10 border border-rose-200/50 p-3">
      {/* Header صغير */}
      <div className="flex items-center justify-between mb-2">
        <div className="flex items-center gap-2">
          <div className="p-1.5 bg-gradient-to-br from-rose-500 to-orange-500 rounded-lg">
            <Flame className="w-4 h-4 text-white" />
          </div>
          <h3 className="text-sm font-bold text-gray-800">
            {isArabic ? '🔥 عروض حصرية' : '🔥 Exclusive Offers'}
          </h3>
          <span className="px-1.5 py-0.5 bg-rose-100 text-rose-600 text-xs rounded-full">
            {offers.length}
          </span>
        </div>

        <div className="flex items-center gap-1">
          <button
            onClick={() => scroll('right')}
            className="p-1 hover:bg-gray-200/50 rounded-full transition-colors"
          >
            <ChevronRight className="w-4 h-4 text-gray-500" />
          </button>
          <button
            onClick={() => scroll('left')}
            className="p-1 hover:bg-gray-200/50 rounded-full transition-colors"
          >
            <ChevronLeft className="w-4 h-4 text-gray-500" />
          </button>
        </div>
      </div>

      {/* Offers Carousel مصغر */}
      <div
        ref={scrollRef}
        className="flex gap-2 overflow-x-auto pb-1 scrollbar-hide scroll-smooth"
        style={{ scrollbarWidth: 'none', msOverflowStyle: 'none' }}
      >
        {offers.map((offer) => (
          <motion.div
            key={offer.id}
            initial={{ opacity: 0, scale: 0.95 }}
            animate={{ opacity: 1, scale: 1 }}
            className="flex-shrink-0 w-36 bg-white rounded-lg shadow-sm border border-gray-100 overflow-hidden hover:shadow-md transition-shadow cursor-pointer"
          >
            {/* Image صغيرة */}
            <div className="relative h-16 overflow-hidden">
              <img
                src={offer.image}
                alt={isArabic ? offer.title : offer.titleEn}
                className="w-full h-full object-cover"
              />
              <div className="absolute inset-0 bg-gradient-to-t from-black/40 to-transparent" />
              
              {/* Discount Badge صغير */}
              <div className="absolute top-1 right-1 px-1.5 py-0.5 bg-gradient-to-r from-rose-500 to-orange-500 text-white text-xs font-bold rounded flex items-center gap-0.5">
                <Tag className="w-2.5 h-2.5" />
                {offer.discount}
              </div>

              {/* Badge حصري */}
              {offer.badge && (
                <div className="absolute top-1 left-1 px-1 py-0.5 bg-yellow-400 text-yellow-900 text-[10px] font-bold rounded">
                  {isArabic ? offer.badge : offer.badgeEn}
                </div>
              )}
            </div>

            {/* Content مختصر */}
            <div className="p-2">
              <h4 className="text-xs font-semibold text-gray-800 line-clamp-1 mb-0.5">
                {isArabic ? offer.title : offer.titleEn}
              </h4>
              <p className="text-[10px] text-gray-400 flex items-center gap-0.5 mb-1">
                <Store className="w-2.5 h-2.5" />
                {isArabic ? offer.store : offer.storeEn}
              </p>

              {/* Price صغير */}
              <div className="flex items-center gap-1">
                <span className="text-sm font-bold text-rose-600">
                  {offer.newPrice}
                </span>
                <span className="text-[10px] text-gray-400 line-through">
                  {offer.oldPrice}
                </span>
              </div>

              {/* End Time */}
              <div className="flex items-center gap-0.5 mt-1 text-[10px] text-amber-600">
                <Clock className="w-2.5 h-2.5" />
                {offer.endTime}
              </div>
            </div>
          </motion.div>
        ))}
      </div>
    </div>
  );
}
