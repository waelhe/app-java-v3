'use client';

import React, { useState, useRef, useEffect } from 'react';
import { Building, Bed, Bath, Maximize, Heart, Star, Home, Landmark, Building2, Warehouse, Store, ChevronLeft, ChevronRight } from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer } from 'vaul';

interface Property {
  id: string;
  title: string;
  titleEn: string;
  type: string;
  typeEn: string;
  category: 'apartment' | 'villa' | 'office' | 'land' | 'shop';
  categoryAr: string;
  categoryEn: string;
  price: string;
  priceUnit?: string;
  priceUnitEn?: string;
  location: string;
  locationEn: string;
  images: string[];
  beds: number;
  baths: number;
  area: number;
  rating: number;
  reviews: number;
  featured?: boolean;
  new?: boolean;
}

const qudsayaCenterProperties: Property[] = [
  {
    id: '1',
    title: 'شقة فاخرة إطلالة رائعة',
    titleEn: 'Luxury Apartment with Amazing View',
    type: 'للبيع',
    typeEn: 'For Sale',
    category: 'apartment',
    categoryAr: 'شقة',
    categoryEn: 'Apartment',
    price: '280,000',
    location: 'قدسيا - الحي الغربي',
    locationEn: 'Qudsaya - West District',
    images: [
      'https://images.unsplash.com/photo-1600596542815-ffad4c1539a9?auto=format&fit=crop&w=400&q=80',
      'https://images.unsplash.com/photo-1600607687939-ce8a6c25118c?auto=format&fit=crop&w=400&q=80',
    ],
    beds: 4,
    baths: 2,
    area: 180,
    rating: 4.9,
    reviews: 23,
    featured: true
  },
  {
    id: '2',
    title: 'شقة عصرية للإيجار',
    titleEn: 'Modern Apartment for Rent',
    type: 'للإيجار',
    typeEn: 'For Rent',
    category: 'apartment',
    categoryAr: 'شقة',
    categoryEn: 'Apartment',
    price: '450',
    priceUnit: '/شهر',
    priceUnitEn: '/month',
    location: 'قدسيا - المركز',
    locationEn: 'Qudsaya - Center',
    images: [
      'https://images.unsplash.com/photo-1600607687939-ce8a6c25118c?auto=format&fit=crop&w=400&q=80',
      'https://images.unsplash.com/photo-1600566753190-17f0baa2a6c3?auto=format&fit=crop&w=400&q=80',
    ],
    beds: 3,
    baths: 2,
    area: 120,
    rating: 4.7,
    reviews: 15,
    new: true
  },
  {
    id: '3',
    title: 'فيلا فاخرة مع حديقة خاصة',
    titleEn: 'Luxury Villa with Private Garden',
    type: 'للبيع',
    typeEn: 'For Sale',
    category: 'villa',
    categoryAr: 'فيلا',
    categoryEn: 'Villa',
    price: '950,000',
    location: 'قدسيا - الحي الجنوبي',
    locationEn: 'Qudsaya - South District',
    images: [
      'https://images.unsplash.com/photo-1600585154340-be6161a56a0c?auto=format&fit=crop&w=400&q=80',
      'https://images.unsplash.com/photo-1600566753086-00f18fb6b3ea?auto=format&fit=crop&w=400&q=80',
    ],
    beds: 6,
    baths: 4,
    area: 380,
    rating: 5.0,
    reviews: 42,
    featured: true
  },
  {
    id: '4',
    title: 'مكتب تجاري موقع مميز',
    titleEn: 'Commercial Office Prime Location',
    type: 'للإيجار',
    typeEn: 'For Rent',
    category: 'office',
    categoryAr: 'مكتب',
    categoryEn: 'Office',
    price: '700',
    priceUnit: '/شهر',
    priceUnitEn: '/month',
    location: 'قدسيا - الساحة',
    locationEn: 'Qudsaya - Square',
    images: [
      'https://images.unsplash.com/photo-1497366216548-37526070297c?auto=format&fit=crop&w=400&q=80',
    ],
    beds: 0,
    baths: 1,
    area: 80,
    rating: 4.5,
    reviews: 8
  },
  {
    id: '5',
    title: 'شقة غرفة وصالة جديدة',
    titleEn: 'New One Bedroom Apartment',
    type: 'للإيجار',
    typeEn: 'For Rent',
    category: 'apartment',
    categoryAr: 'شقة',
    categoryEn: 'Apartment',
    price: '280',
    priceUnit: '/شهر',
    priceUnitEn: '/month',
    location: 'قدسيا - الحي الشرقي',
    locationEn: 'Qudsaya - East District',
    images: [
      'https://images.unsplash.com/photo-1600566753190-17f0baa2a6c3?auto=format&fit=crop&w=400&q=80',
    ],
    beds: 1,
    baths: 1,
    area: 65,
    rating: 4.6,
    reviews: 12,
    new: true
  },
  {
    id: '6',
    title: 'محل تجاري للإيجار',
    titleEn: 'Commercial Shop for Rent',
    type: 'للإيجار',
    typeEn: 'For Rent',
    category: 'shop',
    categoryAr: 'محل',
    categoryEn: 'Shop',
    price: '550',
    priceUnit: '/شهر',
    priceUnitEn: '/month',
    location: 'قدسيا - الشارع الرئيسي',
    locationEn: 'Qudsaya - Main Street',
    images: [
      'https://images.unsplash.com/photo-1604014237800-1c9102c219da?auto=format&fit=crop&w=400&q=80',
    ],
    beds: 0,
    baths: 1,
    area: 45,
    rating: 4.3,
    reviews: 6
  },
  {
    id: '7',
    title: 'أرض سكنية للبيع',
    titleEn: 'Residential Land for Sale',
    type: 'للبيع',
    typeEn: 'For Sale',
    category: 'land',
    categoryAr: 'أرض',
    categoryEn: 'Land',
    price: '120,000',
    location: 'قدسيا - المنطقة الخضراء',
    locationEn: 'Qudsaya - Green Zone',
    images: [
      'https://images.unsplash.com/photo-1500382017468-9049fed747ef?auto=format&fit=crop&w=400&q=80',
    ],
    beds: 0,
    baths: 0,
    area: 500,
    rating: 4.8,
    reviews: 5
  },
  {
    id: '8',
    title: 'دوبلكس فاخر 240م²',
    titleEn: 'Luxury Duplex 240m²',
    type: 'للبيع',
    typeEn: 'For Sale',
    category: 'apartment',
    categoryAr: 'شقة',
    categoryEn: 'Apartment',
    price: '420,000',
    location: 'قدسيا - برج الورود',
    locationEn: 'Qudsaya - Al Ward Tower',
    images: [
      'https://images.unsplash.com/photo-1600210492493-0946911123ea?auto=format&fit=crop&w=400&q=80',
      'https://images.unsplash.com/photo-1600585154526-990dced4db0d?auto=format&fit=crop&w=400&q=80',
    ],
    beds: 5,
    baths: 3,
    area: 240,
    rating: 4.9,
    reviews: 18,
    featured: true
  }
];

const qudsayaDahiaProperties: Property[] = [
  {
    id: 'd1',
    title: 'شقة عصرية 150م²',
    titleEn: 'Modern Apartment 150m²',
    type: 'للبيع',
    typeEn: 'For Sale',
    category: 'apartment',
    categoryAr: 'شقة',
    categoryEn: 'Apartment',
    price: '250,000',
    location: 'الضاحية - الحي الغربي',
    locationEn: 'Dahia - West District',
    images: [
      'https://images.unsplash.com/photo-1600596542815-ffad4c1539a9?auto=format&fit=crop&w=400&q=80',
    ],
    beds: 3,
    baths: 2,
    area: 150,
    rating: 4.7,
    reviews: 19
  },
  {
    id: 'd2',
    title: 'شقة للإيجار قريبة من الخدمات',
    titleEn: 'Apartment for Rent Near Services',
    type: 'للإيجار',
    typeEn: 'For Rent',
    category: 'apartment',
    categoryAr: 'شقة',
    categoryEn: 'Apartment',
    price: '500',
    priceUnit: '/شهر',
    priceUnitEn: '/month',
    location: 'الضاحية - الحي الرئيسي',
    locationEn: 'Dahia - Main District',
    images: [
      'https://images.unsplash.com/photo-1600607687939-ce8a6c25118c?auto=format&fit=crop&w=400&q=80',
    ],
    beds: 3,
    baths: 2,
    area: 120,
    rating: 4.5,
    reviews: 14
  },
  {
    id: 'd3',
    title: 'فيلا مع مسبح خاص',
    titleEn: 'Villa with Private Pool',
    type: 'للبيع',
    typeEn: 'For Sale',
    category: 'villa',
    categoryAr: 'فيلا',
    categoryEn: 'Villa',
    price: '1,200,000',
    location: 'الضاحية - الحي الجنوبي',
    locationEn: 'Dahia - South District',
    images: [
      'https://images.unsplash.com/photo-1600585154340-be6161a56a0c?auto=format&fit=crop&w=400&q=80',
    ],
    beds: 7,
    baths: 5,
    area: 450,
    rating: 5.0,
    reviews: 35,
    featured: true
  },
  {
    id: 'd4',
    title: 'مكتب في مركز تجاري',
    titleEn: 'Office in Commercial Center',
    type: 'للإيجار',
    typeEn: 'For Rent',
    category: 'office',
    categoryAr: 'مكتب',
    categoryEn: 'Office',
    price: '800',
    priceUnit: '/شهر',
    priceUnitEn: '/month',
    location: 'الضاحية - المركز التجاري',
    locationEn: 'Dahia - Commercial Center',
    images: [
      'https://images.unsplash.com/photo-1497366216548-37526070297c?auto=format&fit=crop&w=400&q=80',
    ],
    beds: 0,
    baths: 1,
    area: 100,
    rating: 4.6,
    reviews: 11
  },
  {
    id: 'd5',
    title: 'شقة صغيرة للعزاب',
    titleEn: 'Small Apartment for Singles',
    type: 'للإيجار',
    typeEn: 'For Rent',
    category: 'apartment',
    categoryAr: 'شقة',
    categoryEn: 'Apartment',
    price: '200',
    priceUnit: '/شهر',
    priceUnitEn: '/month',
    location: 'الضاحية - الحي الشمالي',
    locationEn: 'Dahia - North District',
    images: [
      'https://images.unsplash.com/photo-1600566753190-17f0baa2a6c3?auto=format&fit=crop&w=400&q=80',
    ],
    beds: 1,
    baths: 1,
    area: 50,
    rating: 4.4,
    reviews: 9,
    new: true
  },
  {
    id: 'd6',
    title: 'محل زاوية للإيجار',
    titleEn: 'Corner Shop for Rent',
    type: 'للإيجار',
    typeEn: 'For Rent',
    category: 'shop',
    categoryAr: 'محل',
    categoryEn: 'Shop',
    price: '650',
    priceUnit: '/شهر',
    priceUnitEn: '/month',
    location: 'الضاحية - الساحة',
    locationEn: 'Dahia - Square',
    images: [
      'https://images.unsplash.com/photo-1604014237800-1c9102c219da?auto=format&fit=crop&w=400&q=80',
    ],
    beds: 0,
    baths: 1,
    area: 60,
    rating: 4.2,
    reviews: 7
  },
  {
    id: 'd7',
    title: 'مستودع كبير للبيع',
    titleEn: 'Large Warehouse for Sale',
    type: 'للبيع',
    typeEn: 'For Sale',
    category: 'land',
    categoryAr: 'مستودع',
    categoryEn: 'Warehouse',
    price: '180,000',
    location: 'الضاحية - المنطقة الصناعية',
    locationEn: 'Dahia - Industrial Zone',
    images: [
      'https://images.unsplash.com/photo-1555396273-367ea4eb4db5?auto=format&fit=crop&w=400&q=80',
    ],
    beds: 0,
    baths: 1,
    area: 300,
    rating: 4.0,
    reviews: 4
  },
  {
    id: 'd8',
    title: 'شقة طابق أول 200م²',
    titleEn: 'First Floor Apartment 200m²',
    type: 'للبيع',
    typeEn: 'For Sale',
    category: 'apartment',
    categoryAr: 'شقة',
    categoryEn: 'Apartment',
    price: '380,000',
    location: 'الضاحية - مجمع اليمامة',
    locationEn: 'Dahia - Al Yamama Complex',
    images: [
      'https://images.unsplash.com/photo-1600210491892-03d54c0aaf87?auto=format&fit=crop&w=400&q=80',
    ],
    beds: 4,
    baths: 3,
    area: 200,
    rating: 4.8,
    reviews: 16,
    featured: true
  }
];

const dataByRegion: Record<Region, Property[]> = {
  'qudsaya-center': qudsayaCenterProperties,
  'qudsaya-dahia': qudsayaDahiaProperties
};

const categoryFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: Home },
  { id: 'apartment', name: 'شقق', nameEn: 'Apartments', icon: Building },
  { id: 'villa', name: 'فلل', nameEn: 'Villas', icon: Landmark },
  { id: 'office', name: 'مكاتب', nameEn: 'Offices', icon: Building2 },
  { id: 'shop', name: 'محلات', nameEn: 'Shops', icon: Store },
  { id: 'land', name: 'أراضي', nameEn: 'Lands', icon: Warehouse },
];

export default function RealEstate() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  const properties = dataByRegion[region];
  
  const [activeCategory, setActiveCategory] = useState('all');
  const [favorites, setFavorites] = useState<string[]>([]);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [showAll, setShowAll] = useState(false);
  
  const scrollRef = useRef<HTMLDivElement>(null);

  const filteredProperties = properties.filter(property => {
    return activeCategory === 'all' || property.category === activeCategory;
  });

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
  }, [filteredProperties]);

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
    <section className="py-2">
      <div className="max-w-7xl mx-auto">
        {/* Category Filters - Airbnb Style */}
        <div className="flex gap-6 mb-4 overflow-x-auto pb-2 scrollbar-hide">
          {categoryFilters.map((filter) => {
            const Icon = filter.icon;
            const isActive = activeCategory === filter.id;
            
            return (
              <button
                key={filter.id}
                onClick={() => setActiveCategory(filter.id)}
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
            {filteredProperties.slice(0, 8).map((property) => {
              const isFavorite = favorites.includes(property.id);
              
              return (
                <div
                  key={property.id}
                  className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
                >
                  {/* Image Container - Airbnb Style */}
                  <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                    <img 
                      src={property.images[0]} 
                      alt="" 
                      className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                    />
                  
                  {/* Favorite Button */}
                  <button
                    onClick={(e) => { e.stopPropagation(); toggleFavorite(property.id); }}
                    className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110"
                  >
                    <Heart 
                      className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} 
                    />
                  </button>

                  {/* Type Badge */}
                  <div className="absolute top-2 left-2">
                    <span className={`px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${
                      property.type === 'للبيع' 
                        ? 'bg-teal-600/90 text-white' 
                        : 'bg-amber-500/90 text-white'
                    }`}>
                      {isArabic ? property.type : property.typeEn}
                    </span>
                  </div>

                  {/* Featured Badge */}
                  {property.featured && (
                    <div className="absolute bottom-2 left-2">
                      <span className="px-2 py-0.5 text-[10px] font-semibold rounded-full bg-white/90 text-gray-900">
                        ⭐ {isArabic ? 'مميز' : 'Featured'}
                      </span>
                    </div>
                  )}
                </div>

                {/* Property Info - Airbnb Style */}
                <div>
                  {/* Location */}
                  <p className="text-xs text-gray-500 font-medium mb-0.5">
                    {isArabic ? property.location : property.locationEn}
                  </p>

                  {/* Title */}
                  <h3 className="text-sm font-medium text-gray-900 mb-1 line-clamp-1">
                    {isArabic ? property.title : property.titleEn}
                  </h3>

                  {/* Features */}
                  <div className="flex items-center gap-2 text-xs text-gray-500 mb-1">
                    {property.beds > 0 && (
                      <span className="flex items-center gap-0.5">
                        <Bed className="w-3 h-3" />
                        {property.beds}
                      </span>
                    )}
                    <span className="flex items-center gap-0.5">
                      <Bath className="w-3 h-3" />
                      {property.baths}
                    </span>
                    <span className="flex items-center gap-0.5">
                      <Maximize className="w-3 h-3" />
                      {property.area}م²
                    </span>
                  </div>

                  {/* Price & Rating Row */}
                  <div className="flex items-center justify-between">
                    <div className="flex items-baseline gap-0.5">
                      <span className="text-sm font-bold text-gray-900">$</span>
                      <span className="text-sm font-bold text-gray-900">{property.price}</span>
                      {property.priceUnit && (
                        <span className="text-xs text-gray-500">
                          {isArabic ? property.priceUnit : property.priceUnitEn}
                        </span>
                      )}
                    </div>
                    <div className="flex items-center gap-0.5">
                      <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                      <span className="text-xs font-medium">{property.rating}</span>
                    </div>
                  </div>
                </div>
              </div>
            );
          })}
          
          {/* View All Card */}
          <button
            onClick={() => setShowAll(true)}
            className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
          >
            <div className="aspect-square rounded-xl overflow-hidden relative bg-gray-100">
              <div className="grid grid-cols-2 grid-rows-2 h-full gap-0.5">
                {filteredProperties.slice(0, 4).map((p, i) => (
                  <div key={i} className="overflow-hidden">
                    <img src={p.images[0]} alt="" className="w-full h-full object-cover" />
                  </div>
                ))}
              </div>
              <div className="absolute inset-0 bg-black/40 group-hover:bg-black/50 transition-colors" />
              <div className="absolute inset-0 flex flex-col items-center justify-center text-white">
                <span className="text-base font-semibold">{isArabic ? 'عرض الكل' : 'Show all'}</span>
                <span className="text-xs text-white/80 mt-0.5">{filteredProperties.length} {isArabic ? 'عقار' : 'properties'}</span>
              </div>
            </div>
          </button>
          </div>
        </div>
      </div>

      {/* All Items Drawer */}
      <Drawer.Root open={showAll} onOpenChange={setShowAll}>
        <Drawer.Content className="fixed inset-x-0 bottom-0 h-[90vh] bg-white rounded-t-3xl shadow-2xl z-50">
          <div className="px-4 pt-4 pb-3 border-b border-gray-100">
            <Drawer.Handle className="w-10 h-1 bg-gray-300 rounded-full mx-auto mb-3 block" />
            <Drawer.Title className="text-lg font-bold text-right">
              {isArabic ? 'جميع العقارات' : 'All Properties'}
            </Drawer.Title>
            <p className="text-sm text-gray-500 text-right">
              {filteredProperties.length} {isArabic ? 'عقار متاح' : 'properties available'}
            </p>
          </div>
          <div className="flex-1 overflow-y-auto px-4 py-4" style={{ maxHeight: 'calc(90vh - 100px)' }}>
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-3">
              {filteredProperties.map((property) => {
                const isFavorite = favorites.includes(property.id);
                return (
                  <div key={property.id} className="group cursor-pointer">
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                      <img src={property.images[0]} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                      <button onClick={(e) => { e.stopPropagation(); toggleFavorite(property.id); }} className="absolute top-2 right-2 p-1.5">
                        <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                      </button>
                      <span className={`absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${property.type === 'للبيع' ? 'bg-teal-600/90 text-white' : 'bg-amber-500/90 text-white'}`}>
                        {isArabic ? property.type : property.typeEn}
                      </span>
                    </div>
                    <p className="text-xs text-gray-500 font-medium">{isArabic ? property.location : property.locationEn}</p>
                    <h3 className="text-sm font-medium text-gray-900 line-clamp-1">{isArabic ? property.title : property.titleEn}</h3>
                    <div className="flex items-center gap-2 text-xs text-gray-500 mt-1">
                      {property.beds > 0 && <><Bed className="w-3 h-3" />{property.beds}</>}
                      <span className="flex items-center gap-0.5"><Bath className="w-3 h-3" />{property.baths}</span>
                      <span className="flex items-center gap-0.5"><Maximize className="w-3 h-3" />{property.area}م²</span>
                    </div>
                    <div className="flex items-center justify-between mt-1">
                      <div className="flex items-baseline gap-0.5">
                        <span className="text-sm font-bold text-gray-900">${property.price}</span>
                        {property.priceUnit && <span className="text-xs text-gray-500">{isArabic ? property.priceUnit : property.priceUnitEn}</span>}
                      </div>
                      <div className="flex items-center gap-0.5">
                        <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                        <span className="text-xs font-medium">{property.rating}</span>
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
