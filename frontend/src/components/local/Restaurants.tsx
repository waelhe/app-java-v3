'use client';

import React, { useRef, useState, useEffect } from 'react';
import { UtensilsCrossed, MapPin, Star, Clock, Flame, ChefHat, ChevronLeft, ChevronRight, Heart, ArrowLeft } from 'lucide-react';
import { motion } from 'framer-motion';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import RegionSelector from './RegionSelector';
import { Drawer } from 'vaul';

interface Restaurant {
  id: string;
  name: string;
  nameEn: string;
  cuisine: string;
  rating: number;
  deliveryTime: string;
  image: string;
  isOpen: boolean;
  featured?: boolean;
  new?: boolean;
}

// بيانات ضاحية قدسيا
const qudsayaDahiaRestaurants: Restaurant[] = [
  {
    id: 'd1',
    name: 'مطعم الشام - ضاحية قدسيا',
    nameEn: 'Al-Sham Restaurant - Qudsaya Dahia',
    cuisine: 'شامي',
    rating: 4.8,
    deliveryTime: '30-45',
    image: 'https://images.unsplash.com/photo-1544025162-d76694265947?auto=format&fit=crop&w=300&q=80',
    isOpen: true,
    featured: true
  },
  {
    id: 'd2',
    name: 'بيتزا الضاحية',
    nameEn: 'Dahia Pizza',
    cuisine: 'بيتزا',
    rating: 4.5,
    deliveryTime: '25-35',
    image: 'https://images.unsplash.com/photo-1565299624946-b28f40a0ae38?auto=format&fit=crop&w=300&q=80',
    isOpen: true,
    new: true
  },
  {
    id: 'd3',
    name: 'مشاوي البيك',
    nameEn: 'Al-Baik Grills',
    cuisine: 'مشاوي',
    rating: 4.9,
    deliveryTime: '20-30',
    image: 'https://images.unsplash.com/photo-1603360946369-dc9bb6258143?auto=format&fit=crop&w=300&q=80',
    isOpen: true,
    featured: true
  },
  {
    id: 'd4',
    name: 'كافيه النخيل',
    nameEn: 'Palm Cafe',
    cuisine: 'حلويات',
    rating: 4.6,
    deliveryTime: '15-25',
    image: 'https://images.unsplash.com/photo-1551024506-0bccd828d307?auto=format&fit=crop&w=300&q=80',
    isOpen: true
  },
  {
    id: 'd5',
    name: 'سندويشات الساحة',
    nameEn: 'Square Sandwiches',
    cuisine: 'سندويشات',
    rating: 4.7,
    deliveryTime: '15-20',
    image: 'https://images.unsplash.com/photo-1568901346375-23c9450c58cd?auto=format&fit=crop&w=300&q=80',
    isOpen: true,
    new: true
  },
  {
    id: 'd6',
    name: 'مطعم السياحة',
    nameEn: 'Tourism Restaurant',
    cuisine: 'عربي',
    rating: 4.4,
    deliveryTime: '30-40',
    image: 'https://images.unsplash.com/photo-1517248135467-4c7edcad34c4?auto=format&fit=crop&w=300&q=80',
    isOpen: true
  }
];

// بيانات قدسيا المركز
const qudsayaCenterRestaurants: Restaurant[] = [
  {
    id: 'c1',
    name: 'مطعم القدس',
    nameEn: 'Al-Quds Restaurant',
    cuisine: 'شامي',
    rating: 4.7,
    deliveryTime: '25-40',
    image: 'https://images.unsplash.com/photo-1544025162-d76694265947?auto=format&fit=crop&w=300&q=80',
    isOpen: true,
    featured: true
  },
  {
    id: 'c2',
    name: 'بيتزا الجبل',
    nameEn: 'Mountain Pizza',
    cuisine: 'بيتزا',
    rating: 4.6,
    deliveryTime: '30-40',
    image: 'https://images.unsplash.com/photo-1565299624946-b28f40a0ae38?auto=format&fit=crop&w=300&q=80',
    isOpen: true,
    new: true
  },
  {
    id: 'c3',
    name: 'مشاوي الصفاء',
    nameEn: 'Al-Safa Grills',
    cuisine: 'مشاوي',
    rating: 4.8,
    deliveryTime: '20-35',
    image: 'https://images.unsplash.com/photo-1603360946369-dc9bb6258143?auto=format&fit=crop&w=300&q=80',
    isOpen: true,
    featured: true
  },
  {
    id: 'c4',
    name: 'كافيه الجبل',
    nameEn: 'Mountain Cafe',
    cuisine: 'حلويات',
    rating: 4.5,
    deliveryTime: '15-25',
    image: 'https://images.unsplash.com/photo-1551024506-0bccd828d307?auto=format&fit=crop&w=300&q=80',
    isOpen: true
  },
  {
    id: 'c5',
    name: 'مأكولات البحر',
    nameEn: 'Seafood Kitchen',
    cuisine: 'بحري',
    rating: 4.9,
    deliveryTime: '35-45',
    image: 'https://images.unsplash.com/photo-1615141982883-c7ad0e69fd62?auto=format&fit=crop&w=300&q=80',
    isOpen: true
  },
  {
    id: 'c6',
    name: 'بوفيه الشرق',
    nameEn: 'East Buffet',
    cuisine: 'بوفيه',
    rating: 4.3,
    deliveryTime: '40-50',
    image: 'https://images.unsplash.com/photo-1555396273-367ea4eb4db5?auto=format&fit=crop&w=300&q=80',
    isOpen: true
  }
];

const restaurantsByRegion: Record<Region, Restaurant[]> = {
  'qudsaya-center': qudsayaCenterRestaurants,
  'qudsaya-dahia': qudsayaDahiaRestaurants
};

const cuisineFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: ChefHat },
  { id: 'شامي', name: 'شامي', nameEn: 'Levantine', icon: UtensilsCrossed },
  { id: 'بيتزا', name: 'بيتزا', nameEn: 'Pizza', icon: UtensilsCrossed },
  { id: 'مشاوي', name: 'مشاوي', nameEn: 'Grills', icon: Flame },
];

export default function Restaurants() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region, regionName } = useRegion();
  const restaurants = restaurantsByRegion[region];
  
  const [activeCuisine, setActiveCuisine] = useState('all');
  const [favorites, setFavorites] = useState<string[]>([]);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [showAll, setShowAll] = useState(false);
  
  const scrollRef = useRef<HTMLDivElement>(null);

  const filteredRestaurants = restaurants.filter(r => 
    activeCuisine === 'all' || r.cuisine === activeCuisine
  );

  const checkScroll = () => {
    if (scrollRef.current) {
      const { scrollLeft, scrollWidth, clientWidth } = scrollRef.current;
      setCanScrollLeft(scrollLeft > 0);
      setCanScrollRight(scrollLeft < scrollWidth - clientWidth - 10);
    }
  };

  useEffect(() => {
    checkScroll();
    const scrollEl = scrollRef.current;
    if (scrollEl) {
      scrollEl.addEventListener('scroll', checkScroll);
      return () => scrollEl.removeEventListener('scroll', checkScroll);
    }
  }, [filteredRestaurants]);

  const scroll = (direction: 'left' | 'right') => {
    if (scrollRef.current) {
      scrollRef.current.scrollBy({
        left: direction === 'left' ? -280 : 280,
        behavior: 'smooth'
      });
    }
  };

  const toggleFavorite = (id: string) => {
    setFavorites(prev => 
      prev.includes(id) ? prev.filter(f => f !== id) : [...prev, id]
    );
  };

  return (
    <section id="restaurants" className="py-2">
      <div className="max-w-7xl mx-auto">
        {/* Section Title */}
        <h2 className="text-xl font-bold text-gray-900 mb-4">
          {isArabic ? '🍽️ مطاعم' : '🍽️ Restaurants'}
        </h2>

        {/* Cuisine Filters - Airbnb Style */}
        <div className="flex gap-6 mb-4 overflow-x-auto pb-2 scrollbar-hide">
          {cuisineFilters.map((filter) => {
            const Icon = filter.icon;
            const isActive = activeCuisine === filter.id;
            
            return (
              <button
                key={filter.id}
                onClick={() => setActiveCuisine(filter.id)}
                className={`flex flex-col items-center gap-1.5 pb-2 min-w-[50px] transition-all border-b-2 ${
                  isActive 
                    ? 'border-gray-900 text-gray-900' 
                    : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-200'
                }`}
              >
                <Icon className="w-5 h-5" />
                <span className="text-xs font-medium">{isArabic ? filter.name : filter.nameEn}</span>
              </button>
            );
          })}
        </div>

        {/* Horizontal Scrolling Container - Airbnb Style */}
        <div className="relative">
          {/* Navigation Buttons */}
          {canScrollLeft && (
            <button
              onClick={() => scroll('left')}
              className="absolute left-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform"
            >
              <ChevronRight className="w-4 h-4 text-gray-700" />
            </button>
          )}
          {canScrollRight && (
            <button
              onClick={() => scroll('right')}
              className="absolute right-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform"
            >
              <ChevronLeft className="w-4 h-4 text-gray-700" />
            </button>
          )}

          {/* Scrollable Cards */}
          <div
            ref={scrollRef}
            className="flex gap-4 overflow-x-auto pb-2 scroll-smooth scrollbar-hide"
            style={{ scrollbarWidth: 'none', msOverflowStyle: 'none' }}
          >
            {filteredRestaurants.slice(0, 8).map((restaurant) => {
              const isFavorite = favorites.includes(restaurant.id);
              
              return (
                <div
                  key={restaurant.id}
                  className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
                >
                  {/* Image Container - Airbnb Style */}
                  <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                    <img 
                      src={restaurant.image} 
                      alt="" 
                      className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                    />
                    
                    {/* Favorite Button */}
                    <button
                      onClick={(e) => { e.stopPropagation(); toggleFavorite(restaurant.id); }}
                      className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110"
                    >
                      <Heart 
                        className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} 
                      />
                    </button>

                    {/* Status Badge */}
                    <span className={`absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${
                      restaurant.isOpen ? 'bg-emerald-500/90 text-white' : 'bg-gray-500/90 text-white'
                    }`}>
                      {restaurant.isOpen ? (isArabic ? 'مفتوح' : 'Open') : (isArabic ? 'مغلق' : 'Closed')}
                    </span>

                    {/* Featured/New Badge */}
                    {(restaurant.featured || restaurant.new) && (
                      <div className="absolute bottom-2 left-2">
                        <span className={`px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${
                          restaurant.featured ? 'bg-amber-500/90 text-white' : 'bg-emerald-500/90 text-white'
                        }`}>
                          {restaurant.featured ? (isArabic ? 'مميز' : 'Featured') : (isArabic ? 'جديد' : 'New')}
                        </span>
                      </div>
                    )}
                  </div>

                  {/* Restaurant Info - Airbnb Style */}
                  <div>
                    {/* Cuisine */}
                    <p className="text-xs text-gray-500 font-medium mb-0.5">
                      {restaurant.cuisine}
                    </p>

                    {/* Name */}
                    <h3 className="text-sm font-medium text-gray-900 mb-1 line-clamp-1">
                      {isArabic ? restaurant.name : restaurant.nameEn}
                    </h3>

                    {/* Delivery Time & Rating */}
                    <div className="flex items-center justify-between">
                      <span className="text-xs text-gray-500">
                        🕐 {restaurant.deliveryTime} {isArabic ? 'دقيقة' : 'min'}
                      </span>
                      <div className="flex items-center gap-0.5">
                        <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                        <span className="text-xs font-medium">{restaurant.rating}</span>
                      </div>
                    </div>
                  </div>
                </div>
              );
            })}
            
            {/* View All Card - Airbnb Style */}
            <button
              onClick={() => setShowAll(true)}
              className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
            >
              <div className="aspect-square rounded-xl overflow-hidden relative bg-gray-100">
                {/* Mini grid of images */}
                <div className="grid grid-cols-2 grid-rows-2 h-full gap-0.5">
                  {filteredRestaurants.slice(0, 4).map((r, i) => (
                    <div key={i} className="overflow-hidden">
                      <img 
                        src={r.image} 
                        alt="" 
                        className="w-full h-full object-cover"
                      />
                    </div>
                  ))}
                </div>
                {/* Overlay */}
                <div className="absolute inset-0 bg-black/40 group-hover:bg-black/50 transition-colors" />
                {/* Content */}
                <div className="absolute inset-0 flex flex-col items-center justify-center text-white">
                  <span className="text-base font-semibold">
                    {isArabic ? 'عرض الكل' : 'Show all'}
                  </span>
                  <span className="text-xs text-white/80 mt-0.5">
                    {filteredRestaurants.length} {isArabic ? 'مطعم' : 'places'}
                  </span>
                </div>
              </div>
            </button>
          </div>
        </div>
      </div>

      {/* All Items Drawer */}
      <Drawer.Root open={showAll} onOpenChange={setShowAll}>
        <Drawer.Content className="fixed inset-x-0 bottom-0 h-[90vh] bg-white rounded-t-3xl shadow-2xl z-50">
          {/* Header */}
          <div className="px-4 pt-4 pb-3 border-b border-gray-100">
            <Drawer.Handle className="w-10 h-1 bg-gray-300 rounded-full mx-auto mb-3 block" />
            <Drawer.Title className="text-lg font-bold text-right">
              {isArabic ? 'جميع المطاعم' : 'All Restaurants'}
            </Drawer.Title>
            <p className="text-sm text-gray-500 text-right">
              {filteredRestaurants.length} {isArabic ? 'مطعم متاح' : 'places available'}
            </p>
          </div>
          
          {/* Scrollable Content */}
          <div className="flex-1 overflow-y-auto px-4 py-4" style={{ maxHeight: 'calc(90vh - 100px)' }}>
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-3">
              {filteredRestaurants.map((restaurant) => {
                const isFavorite = favorites.includes(restaurant.id);
                return (
                  <div key={restaurant.id} className="group cursor-pointer">
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                      <img src={restaurant.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                      <button onClick={(e) => { e.stopPropagation(); toggleFavorite(restaurant.id); }} className="absolute top-2 right-2 p-1.5">
                        <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                      </button>
                      <span className={`absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${restaurant.isOpen ? 'bg-emerald-500/90 text-white' : 'bg-gray-500/90 text-white'}`}>
                        {restaurant.isOpen ? (isArabic ? 'مفتوح' : 'Open') : (isArabic ? 'مغلق' : 'Closed')}
                      </span>
                    </div>
                    <p className="text-xs text-gray-500 font-medium">{restaurant.cuisine}</p>
                    <h3 className="text-sm font-medium text-gray-900 line-clamp-1">{isArabic ? restaurant.name : restaurant.nameEn}</h3>
                    <div className="flex items-center justify-between mt-1">
                      <span className="text-xs text-gray-500">🕐 {restaurant.deliveryTime} {isArabic ? 'د' : 'min'}</span>
                      <div className="flex items-center gap-0.5">
                        <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                        <span className="text-xs font-medium">{restaurant.rating}</span>
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        </Drawer.Content>
      </Drawer.Root>
    </section>
  );
}
