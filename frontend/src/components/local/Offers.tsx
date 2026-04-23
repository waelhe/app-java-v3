'use client';

import React from 'react';
import { Tag, Clock, Percent, Store, Flame } from 'lucide-react';
import { motion } from 'framer-motion';
import { useLanguage } from '@/contexts/LanguageContext';
import RegionSelector from './RegionSelector';
import { useRegion, Region } from '@/contexts/RegionContext';

interface Offer {
  id: string;
  title: string;
  titleEn: string;
  store: string;
  storeEn: string;
  discount: string;
  newPrice: string;
  endTime: string;
  image: string;
}

const qudsayaCenterOffers: Offer[] = [
  {
    id: '1',
    title: 'عرض خاص على المنتجات الغذائية',
    titleEn: 'Special food offer',
    store: 'سوبر ماركت قدسيا',
    storeEn: 'Qudsaya Supermarket',
    discount: '30%',
    newPrice: '35,000',
    endTime: 'ينتهي غداً',
    image: 'https://images.unsplash.com/photo-1604719312566-8912e9227c6a?auto=format&fit=crop&w=300&q=80'
  },
  {
    id: '2',
    title: 'خصم على الملابس الشتوية',
    titleEn: 'Winter clothes discount',
    store: 'محل الأناقة',
    storeEn: 'Elegance Store',
    discount: '50%',
    newPrice: '60,000',
    endTime: '3 أيام متبقية',
    image: 'https://images.unsplash.com/photo-1489987707025-afc232f7ea0f?auto=format&fit=crop&w=300&q=80'
  }
];

const qudsayaDahiaOffers: Offer[] = [
  {
    id: '1',
    title: 'عرض خاص على المنتجات الغذائية',
    titleEn: 'Special food offer',
    store: 'سوبر ماركت الضاحية',
    storeEn: 'Dahia Supermarket',
    discount: '25%',
    newPrice: '40,000',
    endTime: 'ينتهي بعد غد',
    image: 'https://images.unsplash.com/photo-1604719312566-8912e9227c6a?auto=format&fit=crop&w=300&q=80'
  },
  {
    id: '2',
    title: 'خصم على الأجهزة الكهربائية',
    titleEn: 'Electronics discount',
    store: 'محل الإلكترونيات',
    storeEn: 'Electronics Store',
    discount: '40%',
    newPrice: '85,000',
    endTime: '5 أيام متبقية',
    image: 'https://images.unsplash.com/photo-1489987707025-afc232f7ea0f?auto=format&fit=crop&w=300&q=80'
  }
];

const dataByRegion: Record<Region, Offer[]> = {
  'qudsaya-center': qudsayaCenterOffers,
  'qudsaya-dahia': qudsayaDahiaOffers
};

export default function Offers() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region, regionName } = useRegion();
  const offers = dataByRegion[region];

  return (
    <section className="py-6 bg-gradient-to-b from-rose-50 to-white">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="flex items-center justify-between mb-5">
          <div className="flex items-center gap-3">
            <div className="p-3 bg-rose-600 rounded-2xl shadow-lg">
              <Tag className="w-7 h-7 text-white" />
            </div>
            <div>
              <h2 className="text-lg font-black text-gray-900">
                {isArabic ? 'العروض والخصومات' : 'Offers & Discounts'}
              </h2>
              <p className="text-sm text-gray-500">
                {isArabic ? `أفضل العروض في محلات ${regionName}` : `Best offers in ${regionName} shops`}
              </p>
            </div>
          </div>
          <RegionSelector />
        </div>

        <div className="grid grid-cols-2 gap-4">
          {offers.map((offer, index) => (
            <motion.div
              key={offer.id}
              initial={{ opacity: 0, scale: 0.95 }}
              animate={{ opacity: 1, scale: 1 }}
              transition={{ delay: index * 0.1 }}
              className="group bg-white rounded-2xl border border-gray-200 hover:shadow-xl overflow-hidden"
            >
              <div className="relative h-32 overflow-hidden">
                <img src={offer.image} alt="" className="w-full h-full object-cover group-hover:scale-110 transition-transform" />
                <span className="absolute top-2 right-2 px-3 py-1 bg-rose-500 text-white text-sm font-black rounded-full flex items-center gap-1">
                  <Flame className="w-4 h-4" />
                  {offer.discount}
                </span>
              </div>
              <div className="p-4">
                <h3 className="text-base font-bold text-gray-900 mb-1">
                  {isArabic ? offer.title : offer.titleEn}
                </h3>
                <p className="text-sm text-gray-500 flex items-center gap-1 mb-2">
                  <Store className="w-3.5 h-3.5" />
                  {isArabic ? offer.store : offer.storeEn}
                </p>
                <div className="flex items-center justify-between">
                  <span className="text-xl font-black text-rose-600">{offer.newPrice} {isArabic ? 'ل.س' : 'S.P.'}</span>
                  <div className="flex items-center gap-1 text-xs text-amber-600">
                    <Clock className="w-3.5 h-3.5" />
                    {offer.endTime}
                  </div>
                </div>
              </div>
            </motion.div>
          ))}
        </div>
      </div>
    </section>
  );
}
